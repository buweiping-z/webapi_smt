using System.ComponentModel.DataAnnotations.Schema;
namespace webapi.Models
{
    [Table("inspection_signatures")]
    public class InspectionSignature
    {
        [Column("id")]
        public int Id { get; set; }

        [Column("device_model")]
        public string DeviceModel { get; set; } = string.Empty;

        [Column("year")]
        public int Year { get; set; }

        [Column("month")]
        public int Month { get; set; }

        [Column("approver")]
        public string? Approver { get; set; }

        [Column("confirmer")]
        public string? Confirmer { get; set; }

        [Column("operator_name")]
        public string? OperatorName { get; set; }

        [Column("created_at")]
        public DateTime CreatedAt { get; set; }

        [Column("updated_at")]
        public DateTime UpdatedAt { get; set; }
    }
}
