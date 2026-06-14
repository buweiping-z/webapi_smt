using System.ComponentModel.DataAnnotations.Schema;

namespace webapi.Models
{
    [Table("qualified_inspectors")]
    public class QualifiedInspector
    {
        [Column("id")]
        public int Id { get; set; }

        [Column("employee_id")]
        public string EmployeeId { get; set; } = string.Empty;

        [Column("last_name")]
        public string LastName { get; set; } = string.Empty;
    }
}
