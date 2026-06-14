using System.ComponentModel.DataAnnotations.Schema;

namespace webapi.Models
{
    public class InspectionResult
    {
        [Column("id")]
        public int Id { get; set; }

        [Column("record_id")]
        public int RecordId { get; set; }

        [Column("item_name")]
        public string ItemName { get; set; } = string.Empty;

        [Column("result_value")]
        public string ResultValue { get; set; } = string.Empty;

        [Column("is_normal")]
        public bool IsNormal { get; set; }

        [Column("remark")]
        public string Remark { get; set; } = string.Empty;

        // 导航属性
        public InspectionRecord? InspectionRecord { get; set; }
    }
}
